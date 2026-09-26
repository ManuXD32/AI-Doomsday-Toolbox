package com.example.llamadroid.audio.music

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** The two constant encodings that can be safely rewritten without requantization. */
internal enum class StableAudio3TfliteWeightType(val bytesPerValue: Int) {
    FP32(4),
    FP16(2)
}

/** A patchable FULLY_CONNECTED weight buffer in a Stable Audio LiteRT graph. */
internal data class StableAudio3TfliteWeight(
    val name: String,
    val shape: IntArray,
    val offset: Long,
    val byteSize: Int,
    val type: StableAudio3TfliteWeightType,
    val operatorIndex: Int
) {
    val shapeKey: List<Int> get() = shape.toList()
}

/**
 * Small, read-only FlatBuffer reader for the TFLite schema used by Stable Audio.
 *
 * The app deliberately does not depend on a generated schema class. Only the
 * Model/SubGraph/OperatorCode/Operator/Tensor/Buffer fields needed to locate
 * FC constants are decoded here. All offsets are kept as unsigned 32-bit
 * values in Longs, so large DiT files remain addressable on 64-bit devices.
 */
internal object StableAudio3TfliteWeights {
    // Values from schema.fbs BuiltinOperator. Keep these local so a generated
    // schema change cannot silently alter the trust boundary of the patcher.
    private const val BUILTIN_DEQUANTIZE = 6
    private const val BUILTIN_FULLY_CONNECTED = 9
    private const val TENSOR_FLOAT32 = 0
    private const val TENSOR_FLOAT16 = 1

    fun discover(file: File): List<StableAudio3TfliteWeight> {
        require(file.isFile) { "Stable Audio DiT is missing: ${file.absolutePath}" }
        RandomAccessFile(file, "r").use { random ->
            val reader = Reader(random)
            require(reader.length >= 8L) { "Stable Audio DiT FlatBuffer is truncated" }
            val identifier = reader.bytes(4L, 4L).toString(Charsets.US_ASCII)
            require(identifier == "TFL3") {
                "Stable Audio DiT is not a TFLite FlatBuffer (identifier $identifier)"
            }
            val model = reader.table(reader.u32(0L))
            val operatorCodes = model.vectorTables(1).map { code ->
                // Newer schemas store the code in builtin_code (uint field 3),
                // while old exports only populated deprecated_builtin_code (byte field 0).
                (code.u32(3) ?: code.u8(0)?.toLong() ?: -1L).toInt()
            }
            val subgraphs = model.vectorTables(2)
            val buffers = model.vectorTables(4)
            require(subgraphs.isNotEmpty()) { "Stable Audio DiT has no subgraph" }
            val graph = subgraphs.first()
            val tensors = graph.vectorTables(0).mapIndexed { index, tensor ->
                Tensor(
                    index = index,
                    shape = tensor.vectorInts(0),
                    type = tensor.u8(1) ?: TENSOR_FLOAT32,
                    bufferIndex = tensor.u32(2)?.toInt() ?: 0,
                    name = tensor.string(3)
                )
            }
            val operators = graph.vectorTables(3).mapIndexed { index, operator ->
                Operator(
                    index = index,
                    opcodeIndex = operator.u32(0)?.toInt() ?: 0,
                    inputs = operator.vectorInts(1),
                    outputs = operator.vectorInts(2)
                ).also { parsed ->
                    parsed.builtinCode = operatorCodes.getOrNull(parsed.opcodeIndex) ?: -1
                }
            }
            val producers = HashMap<Int, Operator>()
            operators.forEach { operator ->
                operator.outputs.forEach { tensorIndex ->
                    if (tensorIndex >= 0) {
                        require(producers.put(tensorIndex, operator) == null) {
                            "Stable Audio DiT has multiple producers for tensor $tensorIndex"
                        }
                    }
                }
            }
            return operators.asSequence()
                .filter { operator ->
                    operator.opcodeIndex in operatorCodes.indices &&
                        operatorCodes[operator.opcodeIndex] == BUILTIN_FULLY_CONNECTED
                }
                .map { operator -> patchableWeight(reader, tensors, buffers, producers, operator) }
                .toList()
        }
    }

    private fun patchableWeight(
        reader: Reader,
        tensors: List<Tensor>,
        buffers: List<Reader.Table>,
        producers: Map<Int, Operator>,
        operator: Operator
    ): StableAudio3TfliteWeight {
        require(operator.inputs.size > 1) {
            "Fully connected operator ${operator.index} has no weight input"
        }
        val weightIndex = operator.inputs[1]
        require(weightIndex in tensors.indices) {
            "Fully connected operator ${operator.index} has an invalid weight tensor"
        }
        val weightTensor = tensors[weightIndex]
        require(weightTensor.shape.size == 2 && weightTensor.shape.all { it > 0 }) {
            "FC weight ${weightTensor.name ?: weightIndex} must be a non-empty 2D tensor"
        }
        var source = weightTensor
        val producer = producers[weightIndex]
        // A DEQUANTIZE output is the only indirection used by W16A32. The
        // producer's builtin code was resolved while constructing the graph.
        if (producer?.builtinCode == BUILTIN_DEQUANTIZE) {
            val sourceIndex = producer.inputs.firstOrNull() ?: -1
            require(sourceIndex in tensors.indices) {
                "DEQUANTIZE for ${weightTensor.name ?: weightIndex} has no source tensor"
            }
            source = tensors[sourceIndex]
            require(source.shape.contentEquals(weightTensor.shape)) {
                "DEQUANTIZE source shape does not match ${weightTensor.name ?: weightIndex}"
            }
        }
        val type = when (source.type) {
            TENSOR_FLOAT32 -> StableAudio3TfliteWeightType.FP32
            TENSOR_FLOAT16 -> StableAudio3TfliteWeightType.FP16
            else -> throw StableAudio3LoraException(
                "FC weight ${weightTensor.name ?: weightIndex} uses unsupported TFLite type ${source.type}; " +
                    "LoRA merging supports only fp32 and w16a32 (fp16) DiT weights; " +
                    "quantized variants require a separate dequantize/requantize pipeline"
            )
        }
        require(source.bufferIndex in buffers.indices) {
            "FC weight ${weightTensor.name ?: weightIndex} references an invalid buffer"
        }
        val data = buffers[source.bufferIndex].vectorBytes(0)
        val elements = weightTensor.shape.fold(1L) { product, dimension ->
            Math.multiplyExact(product, dimension.toLong())
        }
        val expected = Math.multiplyExact(elements, type.bytesPerValue.toLong())
        require(expected == data.length) {
            "FC weight ${weightTensor.name ?: weightIndex} buffer has ${data.length} bytes, expected $expected"
        }
        require(data.start > 0L && data.start + data.length <= reader.length) {
            "FC weight ${weightTensor.name ?: weightIndex} buffer is outside the FlatBuffer"
        }
        require(data.length <= Int.MAX_VALUE) {
            "FC weight ${weightTensor.name ?: weightIndex} is too large to patch"
        }
        return StableAudio3TfliteWeight(
            name = weightTensor.name?.takeIf { it.isNotBlank() } ?: "FullyConnected_${operator.index}",
            shape = weightTensor.shape,
            offset = data.start,
            byteSize = data.length.toInt(),
            type = type,
            operatorIndex = operator.index
        )
    }

    private data class Tensor(
        val index: Int,
        val shape: IntArray,
        val type: Int,
        val bufferIndex: Int,
        val name: String?
    )

    private data class Operator(
        val index: Int,
        val opcodeIndex: Int,
        val inputs: IntArray,
        val outputs: IntArray,
        var builtinCode: Int = -1
    )

    private class Reader(private val file: RandomAccessFile) {
        val length: Long = file.length()

        fun table(offset: Long): Table {
            checkRange(offset, 4L)
            return Table(this, offset)
        }

        fun u8(offset: Long): Int {
            checkRange(offset, 1L)
            file.seek(offset)
            return file.readUnsignedByte()
        }

        fun u16(offset: Long): Int = u8(offset) or (u8(offset + 1L) shl 8)

        fun i32(offset: Long): Int = u32(offset).toInt()

        fun u32(offset: Long): Long {
            var result = 0L
            for (byteIndex in 0..3) {
                result = result or (u8(offset + byteIndex).toLong() shl (byteIndex * 8))
            }
            return result
        }

        fun bytes(offset: Long, count: Long): ByteArray {
            require(count in 0L..Int.MAX_VALUE) { "FlatBuffer byte range is too large" }
            checkRange(offset, count)
            requireMergeHeap(count)
            val bytes = ByteArray(count.toInt())
            file.seek(offset)
            file.readFully(bytes)
            return bytes
        }

        private fun checkRange(offset: Long, count: Long) {
            require(offset >= 0L && count >= 0L && offset <= length - count) {
                "FlatBuffer offset is outside the file"
            }
        }

        class Table(private val reader: Reader, private val base: Long) {
            private val vtable: Long by lazy {
                val distance = reader.i32(base).toLong()
                val target = base - distance
                require(distance != 0L && target >= 0L && target <= reader.length - 4L) {
                    "FlatBuffer table has an invalid vtable"
                }
                target
            }

            fun u8(field: Int): Int? = fieldLocation(field)?.let(reader::u8)

            fun u32(field: Int): Long? = fieldLocation(field)?.let(reader::u32)

            fun string(field: Int): String? {
                val location = fieldLocation(field) ?: return null
                val target = location + reader.u32(location)
                val length = reader.u32(target)
                return reader.bytes(target + 4L, length).toString(Charset.forName("UTF-8"))
            }

            fun vectorInts(field: Int): IntArray {
                val vector = vector(field) ?: return IntArray(0)
                require(vector.length <= Int.MAX_VALUE / 4) { "FlatBuffer integer vector is too large" }
                requireMergeHeap(vector.length.toLong() * 4L)
                return IntArray(vector.length) { index -> reader.i32(vector.start + index * 4L) }
            }

            fun vectorTables(field: Int): List<Table> {
                val vector = vector(field) ?: return emptyList()
                requireMergeHeap(vector.length.toLong() * 32L)
                return List(vector.length) { index ->
                    val element = vector.start + index * 4L
                    tableAt(element)
                }
            }

            fun vectorBytes(field: Int): ByteVector {
                val location = fieldLocation(field) ?: return ByteVector(0L, 0L)
                val target = location + reader.u32(location)
                val length = reader.u32(target)
                val start = target + 4L
                reader.checkVectorRange(start, length, 1L)
                return ByteVector(start, length)
            }

            private fun tableAt(vectorElement: Long): Table {
                val target = vectorElement + reader.u32(vectorElement)
                return reader.table(target)
            }

            private fun fieldLocation(field: Int): Long? {
                require(field >= 0) { "FlatBuffer field index is negative" }
                val vtableLength = reader.u16(vtable).toLong()
                val entry = 4L + field * 2L
                if (entry + 2L > vtableLength) return null
                val relative = reader.u16(vtable + entry)
                return if (relative == 0) null else base + relative
            }

            private fun vector(field: Int): Vector? {
                val location = fieldLocation(field) ?: return null
                val target = location + reader.u32(location)
                val length = reader.u32(target)
                require(length <= Int.MAX_VALUE) { "FlatBuffer vector is too large" }
                val start = target + 4L
                reader.checkVectorRange(start, length, 4L)
                return Vector(start, length.toInt())
            }

            private fun table(offset: Long): Table = reader.table(offset)
        }

        data class Vector(val start: Long, val length: Int)
        data class ByteVector(val start: Long, val length: Long)

        private fun checkVectorRange(start: Long, length: Long, elementWidth: Long) {
            require(length >= 0L && length <= Long.MAX_VALUE / elementWidth) {
                "FlatBuffer vector length is invalid"
            }
            val bytes = length * elementWidth
            require(start >= 0L && start <= this.length - bytes) {
                "FlatBuffer vector is outside the file"
            }
        }
    }
}
