package com.example.llamadroid.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitLogicTest {
    private fun rec(ts: Long, reason: Int = 3, process: String = "p", desc: String = "") =
        ProcessExitRecord(process, reason, 0, desc, ts, 100, 1, 2)

    @Test
    fun categorizesKnownReasons() {
        assertEquals(ProcessExitCategory.LOW_MEMORY, ProcessExitLogic.categorize(3, ""))
        assertEquals(ProcessExitCategory.EXCESSIVE_RESOURCE_USAGE, ProcessExitLogic.categorize(9, ""))
        assertEquals(ProcessExitCategory.USER_REQUESTED, ProcessExitLogic.categorize(10, ""))
        assertEquals(ProcessExitCategory.SIGNALED, ProcessExitLogic.categorize(2, ""))
        assertEquals(ProcessExitCategory.ANR, ProcessExitLogic.categorize(6, ""))
        assertEquals(ProcessExitCategory.CRASH, ProcessExitLogic.categorize(4, ""))
        assertEquals(ProcessExitCategory.CRASH_NATIVE, ProcessExitLogic.categorize(5, ""))
        assertEquals(ProcessExitCategory.DEPENDENCY_DIED, ProcessExitLogic.categorize(12, ""))
        assertEquals(ProcessExitCategory.FREEZER, ProcessExitLogic.categorize(14, ""))
        assertEquals(ProcessExitCategory.PERMISSION_CHANGE, ProcessExitLogic.categorize(8, ""))
        assertEquals(ProcessExitCategory.OTHER, ProcessExitLogic.categorize(13, "whatever"))
        assertEquals(ProcessExitCategory.OTHER, ProcessExitLogic.categorize(99, ""))
    }

    @Test
    fun foregroundServiceTimeoutDescriptionWinsOverReason() {
        val d = "A foreground service of type dataSync did not stop within its timeout"
        assertEquals(ProcessExitCategory.FGS_TIMEOUT, ProcessExitLogic.categorize(4, d))
        assertFalse(ProcessExitLogic.isForegroundServiceTimeout("user removed task"))
    }

    @Test
    fun newSinceFiltersSortsAndDedupes() {
        val list = listOf(rec(10), rec(30), rec(30), rec(20), rec(5), rec(40, reason = 1))
        val fresh = ProcessExitLogic.newSince(list, 10)
        assertEquals(listOf(30L, 20L), fresh.map { it.timestamp })
        assertTrue(ProcessExitLogic.newSince(list, 40).isEmpty())
    }

    @Test
    fun mergeKeepsNewestFirstAndCaps() {
        val merged = ProcessExitLogic.merge(listOf(rec(1), rec(2)), listOf(rec(3), rec(2)), max = 3)
        assertEquals(listOf(3L, 2L, 1L), merged.map { it.timestamp })
        assertEquals(2, ProcessExitLogic.merge(listOf(rec(1), rec(2), rec(3)), emptyList(), max = 2).size)
    }

    @Test
    fun descriptionIsBounded() {
        val text = "a\n".repeat(500)
        val bounded = ProcessExitLogic.boundDescription(text)
        assertTrue(bounded.length <= ProcessExitLogic.MAX_DESCRIPTION)
        assertFalse(bounded.contains('\n'))
    }

    @Test
    fun newestTimestampNeverGoesBackwards() {
        assertEquals(50L, ProcessExitLogic.newestTimestamp(listOf(rec(10)), 50))
        assertEquals(60L, ProcessExitLogic.newestTimestamp(listOf(rec(60), rec(10)), 50))
    }
}
