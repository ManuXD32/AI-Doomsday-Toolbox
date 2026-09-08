package com.example.llamadroid.audio.music

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal data class StableAudio3ParsedAdapter(
    val file: File,
    val digest: String,
    val adapterType: String,
    val scaling: Float,
    val layers: Map<String, StableAudio3LoraLayer>
)

internal object StableAudio3SafeTensors {
    private const val MAX_HEADER_BYTES = 64L * 1024L * 1024L
    private const val MAX_TENSOR_ELEMENTS = Int.MAX_VALUE.toLong()
    private const val NATIVE_MARKER = ".parametrizations.weight.0."

    fun resolveAdapter(path: String): File {
        val input = File(path.trim())
        val file = if (input.isDirectory) {
            val preferred = File(input, "adapter_model.safetensors")
            if (preferred.isFile) preferred else input.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("safetensors", ignoreCase = true) }
                .singleOrNull()
                ?: throw StableAudio3LoraException(
                    "LoRA adapter directory must contain exactly one .safetensors file"
                )
        } else input
        require(file.isFile) { "LoRA adapter is missing: ${file.absolutePath}" }
        require(file.extension.equals("safetensors", ignoreCase = true)) {
            "Only .safetensors LoRA adapters are supported"
        }
        return file
    }

    fun parse(path: String, digest: String, maxDecodedBytes: Long = Runtime.getRuntime().maxMemory() / 4L, checkpoint: () -> Unit = {}): StableAudio3ParsedAdapter {
        val file = resolveAdapter(path)
        val loaded = load(file, maxDecodedBytes, checkpoint)
        val nativeLayers = loaded.tensors.filterKeys { it.contains(NATIVE_MARKER) }
        if (nativeLayers.isNotEmpty()) {
            val grouped = groupNative(nativeLayers)
            val config = loaded.metadata["lora_config"]?.let {
                runCatching { JSONObject(it) }.getOrElse {
                    throw StableAudio3LoraException("${file.name}: lora_config is malformed")
                }
            }
            val rank = config?.optInt("rank", inferRank(grouped)) ?: inferRank(grouped)
            require(rank > 0) { "${file.name}: LoRA rank must be positive" }
            val alpha = config?.optDouble("alpha", rank.toDouble())?.toFloat() ?: rank.toFloat()
            val type = normalizeType(config?.optString("adapter_type", "lora") ?: "lora")
            return StableAudio3ParsedAdapter(file, digest, type, alpha / rank.toFloat(), grouped)
        }

        if (loaded.tensors.keys.any { it.endsWith(".lora_A.weight") }) {
            val configFile = File(file.parentFile, "adapter_config.json")
            require(configFile.isFile) { "${file.name}: PEFT adapter_config.json is missing" }
            val config = runCatching { JSONObject(configFile.readText()) }.getOrElse {
                throw StableAudio3LoraException("${file.name}: adapter_config.json is malformed")
            }
            val rank = config.optInt("r", 0)
            require(rank > 0) { "${file.name}: PEFT rank is missing" }
            val alpha = config.optDouble("lora_alpha", rank.toDouble()).toFloat()
            val useDora = config.optBoolean("use_dora", false)
            val useRslora = config.optBoolean("use_rslora", false)
            val scaling = alpha / if (useRslora) kotlin.math.sqrt(rank.toDouble()).toFloat() else rank.toFloat()
            val type = if (useDora) "dora-rows" else "lora"
            return StableAudio3ParsedAdapter(file, digest, type, scaling, groupPeft(loaded.tensors))
        }
        throw StableAudio3LoraException(
            "${file.name}: no recognized Stable Audio LoRA tensors were found"
        )
    }

    fun sha256(file: File, onBytes: ((Long, Long) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var completed = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                completed += read
                onBytes?.invoke(completed, total)
            }
        }
        return digest.digest().toHex()
    }

    private data class Loaded(val tensors: Map<String, StableAudio3LoraTensor>, val metadata: Map<String, String>)

    private fun load(file: File, maxDecodedBytes: Long, checkpoint: () -> Unit): Loaded {
        require(!file.name.endsWith(".ckpt", true) && !file.name.endsWith(".pt", true) &&
            !file.name.endsWith(".pth", true) && !file.name.endsWith(".bin", true)) {
            "Pickle-backed LoRA formats are not supported"
        }
        RandomAccessFile(file, "r").use { random ->
            require(random.length() >= 8L) { "${file.name}: safetensors header is truncated" }
            val headerLength = readLongLittleEndian(random)
            require(headerLength in 2L..MAX_HEADER_BYTES && 8L + headerLength <= random.length()) {
                "${file.name}: safetensors header length is invalid"
            }
            requireMergeHeap(headerLength * 6L)
            val headerBytes = ByteArray(headerLength.toInt())
            random.readFully(headerBytes)
            val header = runCatching { JSONObject(String(headerBytes, Charsets.UTF_8)) }.getOrElse {
                throw StableAudio3LoraException("${file.name}: safetensors header is malformed")
            }
            val metadata = mutableMapOf<String, String>()
            header.optJSONObject("__metadata__")?.let { metadataObject ->
                val keys = metadataObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    metadata[key] = metadataObject.optString(key, "")
                }
            }
            val tensors = linkedMapOf<String, StableAudio3LoraTensor>()
            val dataStart = 8L + headerLength
            var decodedBytes = 0L
            val keys = header.keys()
            while (keys.hasNext()) {
                checkpoint()
                val name = keys.next()
                if (name == "__metadata__") continue
                val descriptor = header.optJSONObject(name)
                    ?: throw StableAudio3LoraException("${file.name}: tensor $name descriptor is invalid")
                val shape = parseShape(name, descriptor)
                decodedBytes = Math.addExact(decodedBytes, shape.fold(4L) { n, dim -> Math.multiplyExact(n, dim.toLong()) })
                require(decodedBytes <= maxDecodedBytes) { "Not enough memory to prepare this LoRA stack" }
                val dtype = descriptor.optString("dtype", "")
                val offsets = descriptor.optJSONArray("data_offsets")
                    ?: throw StableAudio3LoraException("$name: data_offsets are missing")
                require(offsets.length() == 2) { "$name: data_offsets must contain [start,end]" }
                val start = offsets.optLong(0, -1L)
                val end = offsets.optLong(1, -1L)
                require(start >= 0L && end >= start && dataStart + end <= random.length()) {
                    "$name: tensor byte range is invalid"
                }
                tensors[name] = StableAudio3LoraTensor(
                    shape = shape,
                    values = readValues(random, dataStart + start, end - start, dtype, shape, name)
                )
            }
            return Loaded(tensors, metadata)
        }
    }

    private fun parseShape(name: String, descriptor: JSONObject): IntArray {
        val array = descriptor.optJSONArray("shape")
            ?: throw StableAudio3LoraException("$name: shape is missing")
        val shape = IntArray(array.length())
        var elements = 1L
        for (index in shape.indices) {
            val dimension = array.optLong(index, -1L)
            require(dimension in 1L..Int.MAX_VALUE) { "$name: shape dimension is invalid" }
            shape[index] = dimension.toInt()
            elements = Math.multiplyExact(elements, dimension)
            require(elements <= MAX_TENSOR_ELEMENTS) { "$name: tensor is too large" }
        }
        return shape
    }

    private fun readValues(
        file: RandomAccessFile,
        offset: Long,
        byteCount: Long,
        dtype: String,
        shape: IntArray,
        name: String
    ): FloatArray {
        val elements = shape.fold(1L) { value, dimension -> Math.multiplyExact(value, dimension.toLong()) }
        require(elements <= Int.MAX_VALUE) { "$name: tensor is too large" }
        val bytesPerElement = when (dtype) {
            "F32" -> 4
            "F16", "BF16" -> 2
            else -> throw StableAudio3LoraException("$name: unsupported safetensors dtype $dtype")
        }
        require(byteCount == elements * bytesPerElement) { "$name: tensor byte count does not match shape" }
        require(byteCount <= Int.MAX_VALUE) { "$name: tensor payload is too large" }
        requireMergeHeap(byteCount + elements * 4L)
        val bytes = ByteArray(byteCount.toInt())
        file.seek(offset)
        file.readFully(bytes)
        val result = FloatArray(elements.toInt())
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (dtype) {
            "F32" -> buffer.asFloatBuffer().get(result)
            "F16" -> for (index in result.indices) result[index] = halfToFloat(buffer.getShort().toInt() and 0xffff)
            "BF16" -> for (index in result.indices) result[index] = Float.fromBits((buffer.getShort().toInt() and 0xffff) shl 16)
        }
        require(result.all { it.isFinite() }) { "$name: adapter contains non-finite values" }
        return result
    }

    private fun readLongLittleEndian(file: RandomAccessFile): Long {
        var value = 0L
        for (shift in 0..56 step 8) value = value or ((file.readUnsignedByte().toLong()) shl shift)
        return value
    }

    private fun groupNative(tensors: Map<String, StableAudio3LoraTensor>): Map<String, StableAudio3LoraLayer> {
        val grouped = linkedMapOf<String, MutableMap<String, StableAudio3LoraTensor>>()
        tensors.forEach { (key, tensor) ->
            val separator = key.indexOf(NATIVE_MARKER)
            val layer = key.substring(0, separator)
            val parameter = key.substring(separator + NATIVE_MARKER.length)
            grouped.getOrPut(layer) { linkedMapOf() }[parameter] = tensor
        }
        return grouped.mapValues { StableAudio3LoraLayer(it.value) }
    }

    private fun groupPeft(tensors: Map<String, StableAudio3LoraTensor>): Map<String, StableAudio3LoraLayer> {
        val grouped = linkedMapOf<String, MutableMap<String, StableAudio3LoraTensor>>()
        tensors.forEach { (rawName, tensor) ->
            val name = rawName.removePrefix("base_model.model.")
            val mapping = when {
                name.endsWith(".lora_A.weight") -> ".lora_A.weight" to "lora_A"
                name.endsWith(".lora_B.weight") -> ".lora_B.weight" to "lora_B"
                name.endsWith(".lora_magnitude_vector.weight") -> ".lora_magnitude_vector.weight" to "magnitude"
                else -> null
            } ?: return@forEach
            val layer = name.removeSuffix(mapping.first)
            grouped.getOrPut(layer) { linkedMapOf() }[mapping.second] = tensor
        }
        return grouped.mapValues { StableAudio3LoraLayer(it.value) }
    }

    private fun inferRank(layers: Map<String, StableAudio3LoraLayer>): Int =
        layers.values.firstNotNullOfOrNull { it.parameters["lora_A"]?.shape?.getOrNull(0) }
            ?: layers.values.firstNotNullOfOrNull { it.parameters["M_xs"]?.shape?.getOrNull(0) }
            ?: throw StableAudio3LoraException("LoRA rank cannot be inferred")

    private fun normalizeType(value: String): String = when (value.trim().lowercase()) {
        "dora" -> "dora-rows"
        "lora", "dora-rows", "dora-cols", "bora", "lora-xs", "dora-rows-xs", "dora-cols-xs", "bora-xs" -> value.trim().lowercase()
        else -> throw StableAudio3LoraException("Unsupported Stable Audio adapter type $value")
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = (bits ushr 15) and 1
        val exponent = (bits ushr 10) and 0x1f
        val fraction = bits and 0x3ff
        val result = when (exponent) {
            0 -> if (fraction == 0) sign shl 31 else {
                var mantissa = fraction
                var exp = -14
                while ((mantissa and 0x400) == 0) {
                    mantissa = mantissa shl 1
                    exp--
                }
                ((sign shl 31) or ((exp + 127) shl 23) or ((mantissa and 0x3ff) shl 13))
            }
            0x1f -> (sign shl 31) or 0x7f800000 or (fraction shl 13)
            else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (fraction shl 13)
        }
        return Float.fromBits(result)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Reserve headroom for the UI and subsequent merge buffers before allocating custom tensors. */
internal fun requireMergeHeap(bytes: Long) {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    require(bytes >= 0L && bytes <= available / 2L) {
        "Not enough memory to prepare this LoRA; reduce the adapter stack or rank"
    }
}
