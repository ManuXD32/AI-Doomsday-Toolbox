package com.example.llamadroid.branchy.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyWorkerProgressEventTest {
    @Test
    fun parsesDpProgress() {
        val e = BranchyWorkerEventParser.parse(
            """BRANCHY_EVENT {"type":"dp_progress","round":3,"phase":"compute","done":4,"total":10,"ms_per_mb":850.5,"eta_s":12}"""
        ) as BranchyWorkerEvent.Progress
        val p = e.progress
        assertTrue(p.dataParallel)
        assertEquals(3, p.round)
        assertEquals("compute", p.phase)
        assertEquals(6, p.left)
        assertEquals(850.5, p.msPerMicro!!, 0.001)
        assertEquals(12.0, p.etaSeconds!!, 0.001)
    }

    @Test
    fun parsesBpProgress() {
        val p = (BranchyWorkerEventParser.parse(
            """{"event":"bp_progress","step":7,"mb":2,"of":8,"ms_fwd":10.5,"ms_bwd":21}"""
        ) as BranchyWorkerEvent.Progress).progress
        assertFalse(p.dataParallel)
        assertEquals(7, p.round)
        assertEquals(2, p.done)
        assertEquals(8, p.total)
        assertEquals(21.0, p.msBwd!!, 0.001)
    }

    @Test
    fun malformedProgressIsIgnoredAndDoesNotChangeWaiting() {
        assertNull(BranchyWorkerEventParser.parse("""{"type":"dp_progress","phase":"wait"}"""))
        val e = BranchyWorkerEventParser.parse("""{"type":"bp_progress","step":1,"mb":1,"of":2}""")
        assertFalse(BranchyWorkerEventParser.waitingAfter(false, e))
    }
}
