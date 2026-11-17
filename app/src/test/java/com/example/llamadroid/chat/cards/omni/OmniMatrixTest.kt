package com.example.llamadroid.chat.cards.omni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniMatrixTest {
    private val a = listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))
    private val b = listOf(listOf(0.0, 1.0), listOf(1.0, 0.0))

    private fun m(r: OmniMatrix.Result) = (r as OmniMatrix.Result.Ok).matrix!!

    @Test fun addSubMulTranspose() {
        assertEquals(listOf(listOf(1.0, 3.0), listOf(4.0, 4.0)), m(OmniMatrix.add(a, b)))
        assertEquals(listOf(listOf(1.0, 1.0), listOf(2.0, 4.0)), m(OmniMatrix.sub(a, b)))
        assertEquals(listOf(listOf(2.0, 1.0), listOf(4.0, 3.0)), m(OmniMatrix.mul(a, b)))
        assertEquals(listOf(listOf(1.0, 3.0), listOf(2.0, 4.0)), m(OmniMatrix.transpose(a)))
        val wide = listOf(listOf(1.0, 2.0, 3.0))
        assertEquals(listOf(listOf(1.0), listOf(2.0), listOf(3.0)), m(OmniMatrix.transpose(wide)))
        assertEquals(listOf(listOf(14.0)), m(OmniMatrix.mul(wide, m(OmniMatrix.transpose(wide)))))
    }

    @Test fun determinantAndInverse() {
        assertEquals(-2.0, (OmniMatrix.det(a) as OmniMatrix.Result.Ok).scalar!!, 1e-9)
        val inv = m(OmniMatrix.inverse(a))
        assertEquals(-2.0, inv[0][0], 1e-9); assertEquals(1.0, inv[0][1], 1e-9)
        assertEquals(1.5, inv[1][0], 1e-9); assertEquals(-0.5, inv[1][1], 1e-9)
        val id = m(OmniMatrix.mul(a, inv))
        assertEquals(1.0, id[0][0], 1e-9); assertEquals(0.0, id[0][1], 1e-9)
        val three = listOf(listOf(2.0, 0.0, 1.0), listOf(1.0, 3.0, 2.0), listOf(1.0, 1.0, 1.0))
        assertEquals(2.0 * (3 - 2) - 0 + 1.0 * (1 - 3), (OmniMatrix.det(three) as OmniMatrix.Result.Ok).scalar!!, 1e-9)
    }

    @Test fun errors() {
        val sing = listOf(listOf(1.0, 2.0), listOf(2.0, 4.0))
        assertEquals(OmniMatrix.Result.Error("singular"), OmniMatrix.inverse(sing))
        assertEquals(OmniMatrix.Result.Error("dims"), OmniMatrix.add(a, listOf(listOf(1.0, 2.0, 3.0))))
        assertEquals(OmniMatrix.Result.Error("dims"), OmniMatrix.mul(a, listOf(listOf(1.0, 2.0, 3.0))))
        assertEquals(OmniMatrix.Result.Error("not_square"), OmniMatrix.det(listOf(listOf(1.0, 2.0, 3.0))))
        assertEquals(OmniMatrix.Result.Error("not_square"), OmniMatrix.inverse(listOf(listOf(1.0), listOf(2.0))))
        assertTrue(OmniMatrix.det(a) is OmniMatrix.Result.Ok)
    }
}
