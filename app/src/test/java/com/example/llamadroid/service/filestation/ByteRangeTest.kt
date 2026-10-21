package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.http.ByteRange
import com.example.llamadroid.service.filestation.http.RangeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteRangeTest {
    private fun partial(h: String, size: Long = 100) = ByteRange.parse(h, size) as RangeResult.Partial

    @Test fun `closed open and suffix ranges`() {
        assertEquals(RangeResult.Partial(0, 9), partial("bytes=0-9"))
        assertEquals(RangeResult.Partial(90, 99), partial("bytes=90-"))
        assertEquals(RangeResult.Partial(95, 99), partial("bytes=-5"))
        assertEquals(RangeResult.Partial(0, 99), partial("bytes=-500"))
        assertEquals(RangeResult.Partial(10, 99), partial("bytes=10-5000"))
        assertEquals(10L, partial("bytes=0-9").length)
    }

    @Test fun `invalid ranges are unsatisfiable`() {
        assertSame(RangeResult.Unsatisfiable, ByteRange.parse("bytes=100-", 100))
        assertSame(RangeResult.Unsatisfiable, ByteRange.parse("bytes=50-10", 100))
        assertSame(RangeResult.Unsatisfiable, ByteRange.parse("bytes=-0", 100))
        assertSame(RangeResult.Unsatisfiable, ByteRange.parse("bytes=abc-", 100))
        assertSame(RangeResult.Unsatisfiable, ByteRange.parse("bytes=5", 100))
    }

    @Test fun `no header multi range and other units give full`() {
        assertSame(RangeResult.Full, ByteRange.parse(null, 100))
        assertSame(RangeResult.Full, ByteRange.parse("bytes=0-1,5-6", 100))
        assertSame(RangeResult.Full, ByteRange.parse("items=0-1", 100))
        assertSame(RangeResult.Full, ByteRange.parse("bytes=0-5", 0))
    }

    @Test fun `if none match handles lists weak tags and star`() {
        assertTrue(ByteRange.noneMatchHit("\"a\"", "\"a\""))
        assertTrue(ByteRange.noneMatchHit("\"x\", W/\"a\"", "\"a\""))
        assertTrue(ByteRange.noneMatchHit("*", "\"a\""))
        assertFalse(ByteRange.noneMatchHit("\"b\"", "\"a\""))
        assertFalse(ByteRange.noneMatchHit(null, "\"a\""))
    }

    @Test fun `if range requires the same strong tag`() {
        assertTrue(ByteRange.ifRangeAllowsRange(null, "\"a\""))
        assertTrue(ByteRange.ifRangeAllowsRange("\"a\"", "\"a\""))
        assertFalse(ByteRange.ifRangeAllowsRange("\"b\"", "\"a\""))
        assertFalse(ByteRange.ifRangeAllowsRange("W/\"a\"", "\"a\""))
        assertFalse(ByteRange.ifRangeAllowsRange("Sat, 01 Jan 2000 00:00:00 GMT", "\"a\""))
    }

    @Test fun `if match is strong and supports star`() {
        assertTrue(ByteRange.matchHit("*", "\"a\""))
        assertTrue(ByteRange.matchHit("\"a\"", "\"a\""))
        assertFalse(ByteRange.matchHit("\"b\"", "\"a\""))
    }
}
