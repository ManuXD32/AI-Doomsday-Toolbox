package com.example.llamadroid.audio.music

import kotlin.math.sqrt

/** A decoded safetensors tensor. All merge arithmetic is performed in FP32. */
internal data class StableAudio3LoraTensor(
    val shape: IntArray,
    val values: FloatArray
) {
    val rank: Int get() = shape.size
}

internal data class StableAudio3LoraLayer(
    val parameters: Map<String, StableAudio3LoraTensor>
)

internal object StableAudio3LoraMath {
    private const val EPSILON = 1.0e-12f

    fun validateAndMergeDelta(
        layerName: String,
        base: FloatArray,
        rows: Int,
        columns: Int,
        layer: StableAudio3LoraLayer,
        adapterType: String,
        scaling: Float,
        checkpoint: () -> Unit = {}
    ): FloatArray {
        require(base.size == rows * columns) { "$layerName: base shape is invalid" }
        val normalizedType = if (adapterType == "dora") "dora-rows" else adapterType
        if (normalizedType.endsWith("-xs")) {
            throw StableAudio3LoraException(
                "$layerName: $normalizedType requires an SVD basis and is not supported by the Android merger"
            )
        }
        val a = layer.parameters["lora_A"]
            ?: throw StableAudio3LoraException("$layerName: adapter is missing lora_A")
        val b = layer.parameters["lora_B"]
            ?: throw StableAudio3LoraException("$layerName: adapter is missing lora_B")
        requireMatrix(layerName, "lora_A", a, a.shape.getOrNull(0) ?: 0, columns)
        requireMatrix(layerName, "lora_B", b, rows, b.shape.getOrNull(1) ?: 0)
        val rank = a.shape[0]
        require(rank == b.shape[1]) {
            "$layerName: lora_B rank ${b.shape[1]} does not match lora_A rank $rank"
        }
        require(scaling.isFinite()) { "$layerName: adapter scaling is not finite" }
        val lowRank = multiply(b.values, rows, rank, a.values, rank, columns, checkpoint)
        if (normalizedType == "lora") {
            return FloatArray(lowRank.size) { lowRank[it] * scaling }
        }

        val modified = FloatArray(base.size) { index -> base[index] + scaling * lowRank[index] }
        val merged = when (normalizedType) {
            "dora-rows" -> applyDoraRows(layerName, modified, rows, columns, layer.parameters["magnitude"], checkpoint)
            "dora-cols" -> applyDoraColumns(layerName, modified, rows, columns, layer.parameters["magnitude"], checkpoint)
            "bora" -> applyBora(layerName, modified, rows, columns, layer.parameters, checkpoint)
            else -> throw StableAudio3LoraException("$layerName: unsupported adapter type $normalizedType")
        }
        return FloatArray(base.size) { index -> merged[index] - base[index] }
    }

    private fun applyDoraRows(
        layerName: String,
        values: FloatArray,
        rows: Int,
        columns: Int,
        magnitude: StableAudio3LoraTensor?,
        checkpoint: () -> Unit
    ): FloatArray {
        val scale = magnitudeVector(layerName, "magnitude", magnitude, rows)
        val result = FloatArray(values.size)
        for (row in 0 until rows) {
            checkpoint()
            var norm = 0.0
            for (column in 0 until columns) {
                val value = values[row * columns + column].toDouble()
                norm += value * value
            }
            val factor = scale[row] / sqrt(norm).toFloat().coerceAtLeast(EPSILON)
            for (column in 0 until columns) result[row * columns + column] = values[row * columns + column] * factor
        }
        return result
    }

    private fun applyDoraColumns(
        layerName: String,
        values: FloatArray,
        rows: Int,
        columns: Int,
        magnitude: StableAudio3LoraTensor?,
        checkpoint: () -> Unit
    ): FloatArray {
        val scale = magnitudeVector(layerName, "magnitude", magnitude, columns)
        val result = FloatArray(values.size)
        for (column in 0 until columns) {
            checkpoint()
            var norm = 0.0
            for (row in 0 until rows) {
                val value = values[row * columns + column].toDouble()
                norm += value * value
            }
            val factor = scale[column] / sqrt(norm).toFloat().coerceAtLeast(EPSILON)
            for (row in 0 until rows) result[row * columns + column] = values[row * columns + column] * factor
        }
        return result
    }

    private fun applyBora(
        layerName: String,
        values: FloatArray,
        rows: Int,
        columns: Int,
        parameters: Map<String, StableAudio3LoraTensor>,
        checkpoint: () -> Unit
    ): FloatArray {
        val rowScale = magnitudeVector(layerName, "magnitude_r", parameters["magnitude_r"], rows)
        val columnScale = magnitudeVector(layerName, "magnitude_c", parameters["magnitude_c"], columns)
        val rowNormalized = FloatArray(values.size)
        for (row in 0 until rows) {
            checkpoint()
            var norm = 0.0
            for (column in 0 until columns) {
                val value = values[row * columns + column].toDouble()
                norm += value * value
            }
            val factor = rowScale[row] / sqrt(norm).toFloat().coerceAtLeast(EPSILON)
            for (column in 0 until columns) rowNormalized[row * columns + column] = values[row * columns + column] * factor
        }
        val result = FloatArray(values.size)
        for (column in 0 until columns) {
            checkpoint()
            var norm = 0.0
            for (row in 0 until rows) {
                val value = rowNormalized[row * columns + column].toDouble()
                norm += value * value
            }
            val factor = columnScale[column] / sqrt(norm).toFloat().coerceAtLeast(EPSILON)
            for (row in 0 until rows) result[row * columns + column] = rowNormalized[row * columns + column] * factor
        }
        return result
    }

    private fun magnitudeVector(
        layerName: String,
        parameter: String,
        tensor: StableAudio3LoraTensor?,
        expected: Int
    ): FloatArray {
        require(tensor != null) { "$layerName: adapter is missing $parameter" }
        require(tensor.values.size == expected) {
            "$layerName: $parameter has ${tensor.values.size} values, expected $expected"
        }
        return tensor.values
    }

    private fun requireMatrix(
        layerName: String,
        parameter: String,
        tensor: StableAudio3LoraTensor,
        expectedRows: Int,
        expectedColumns: Int
    ) {
        require(tensor.rank == 2 && tensor.shape[0] == expectedRows && tensor.shape[1] == expectedColumns) {
            "$layerName: $parameter shape ${tensor.shape.contentToString()} does not fit [$expectedRows,$expectedColumns]"
        }
    }

    private fun multiply(
        left: FloatArray,
        leftRows: Int,
        shared: Int,
        right: FloatArray,
        rightShared: Int,
        rightColumns: Int,
        checkpoint: () -> Unit
    ): FloatArray {
        require(shared == rightShared)
        val result = FloatArray(leftRows * rightColumns)
        for (row in 0 until leftRows) {
            checkpoint()
            val resultRow = row * rightColumns
            for (inner in 0 until shared) {
                val leftValue = left[row * shared + inner]
                val rightRow = inner * rightColumns
                for (column in 0 until rightColumns) {
                    result[resultRow + column] += leftValue * right[rightRow + column]
                }
            }
        }
        return result
    }
}

class StableAudio3LoraException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
