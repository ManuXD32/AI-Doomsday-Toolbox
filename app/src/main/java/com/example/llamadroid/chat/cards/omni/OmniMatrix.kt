package com.example.llamadroid.chat.cards.omni

import kotlin.math.abs

/** Pure matrix math on row lists (matrices up to 4x4 in the UI, any size here). */
object OmniMatrix {
    sealed interface Result {
        /** Exactly one of [matrix] / [scalar] is set. */
        data class Ok(val matrix: List<List<Double>>? = null, val scalar: Double? = null) : Result
        /** [reason]: "dims", "singular" or "not_square". */
        data class Error(val reason: String) : Result
    }

    private const val SINGULAR_EPS = 1e-12

    private fun valid(m: List<List<Double>>) = m.isNotEmpty() && m[0].isNotEmpty() && m.all { it.size == m[0].size }
    private fun square(m: List<List<Double>>) = m.size == m[0].size

    fun add(a: List<List<Double>>, b: List<List<Double>>): Result = zip(a, b) { x, y -> x + y }
    fun sub(a: List<List<Double>>, b: List<List<Double>>): Result = zip(a, b) { x, y -> x - y }

    private fun zip(a: List<List<Double>>, b: List<List<Double>>, f: (Double, Double) -> Double): Result {
        if (!valid(a) || !valid(b) || a.size != b.size || a[0].size != b[0].size) return Result.Error("dims")
        return Result.Ok(matrix = a.indices.map { i -> a[i].indices.map { j -> f(a[i][j], b[i][j]) } })
    }

    fun mul(a: List<List<Double>>, b: List<List<Double>>): Result {
        if (!valid(a) || !valid(b) || a[0].size != b.size) return Result.Error("dims")
        return Result.Ok(matrix = a.indices.map { i ->
            b[0].indices.map { j -> a[i].indices.sumOf { k -> a[i][k] * b[k][j] } }
        })
    }

    fun transpose(a: List<List<Double>>): Result {
        if (!valid(a)) return Result.Error("dims")
        return Result.Ok(matrix = a[0].indices.map { j -> a.indices.map { i -> a[i][j] } })
    }

    fun det(a: List<List<Double>>): Result {
        if (!valid(a)) return Result.Error("dims")
        if (!square(a)) return Result.Error("not_square")
        return Result.Ok(scalar = determinant(a))
    }

    fun inverse(a: List<List<Double>>): Result {
        if (!valid(a)) return Result.Error("dims")
        if (!square(a)) return Result.Error("not_square")
        val n = a.size
        if (abs(determinant(a)) < SINGULAR_EPS) return Result.Error("singular")
        // Gauss-Jordan with partial pivoting on [A | I].
        val m = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < SINGULAR_EPS) return Result.Error("singular")
            val t = m[c]; m[c] = m[p]; m[p] = t
            val d = m[c][c]
            for (j in 0 until 2 * n) m[c][j] /= d
            for (r in 0 until n) if (r != c) {
                val f = m[r][c]
                if (f != 0.0) for (j in 0 until 2 * n) m[r][j] -= f * m[c][j]
            }
        }
        return Result.Ok(matrix = List(n) { i -> List(n) { j -> m[i][n + j] } })
    }

    /** Determinant by Gaussian elimination with partial pivoting. */
    private fun determinant(a: List<List<Double>>): Double {
        val n = a.size
        val m = Array(n) { i -> DoubleArray(n) { j -> a[i][j] } }
        var det = 1.0
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (m[p][c] == 0.0) return 0.0
            if (p != c) { val t = m[c]; m[c] = m[p]; m[p] = t; det = -det }
            det *= m[c][c]
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (j in c until n) m[r][j] -= f * m[c][j]
            }
        }
        return det
    }
}
