package com.example.llamadroid.branchy.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BranchyInferenceHelperTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sliceBranchesFromName() {
        assertEquals(listOf(1, 2), BranchySliceNames.branches("model.b1-2.slice.gguf"))
        assertEquals(listOf(1, 3), BranchySliceNames.branches("m.b1,3.slice.gguf"))
        assertEquals(listOf(2), BranchySliceNames.branches("m.b2.slice.gguf"))
        assertEquals(emptyList<Int>(), BranchySliceNames.branches("model.slice.gguf"))
        assertEquals(emptyList<Int>(), BranchySliceNames.branches("m.b3-1.slice.gguf"))
        assertTrue(BranchySliceNames.isSlice("A.B1.SLICE.GGUF"))
        assertFalse(BranchySliceNames.isSlice("main.gguf"))
    }

    @Test fun safeNameDropsDirectories() {
        assertEquals("x.slice.gguf", BranchySliceNames.safeName("../../etc/x.slice.gguf"))
        assertEquals("x.slice.gguf", BranchySliceNames.safeName("C:\\tmp\\x.slice.gguf"))
    }

    @Test fun fileReceivedEvent() {
        val dir = tmp.newFolder("files")
        val slice = java.io.File(dir, "m.b1.slice.gguf").apply { writeText("x") }
        val line = """BRANCHY_EVENT {"type":"file_received","name":"m.b1.slice.gguf","sha256":"ab","bytes":1,"path":"${slice.absolutePath}"}"""
        assertEquals(slice.canonicalPath, BranchySliceNames.receivedSlicePath(line, dir)!!.canonicalPath)
        // A path outside the files folder is ignored in favour of <files>/<name>.
        val outside = line.replace(slice.absolutePath, "/etc/passwd")
        assertEquals(slice.canonicalPath, BranchySliceNames.receivedSlicePath(outside, dir)!!.canonicalPath)
        assertNull(BranchySliceNames.receivedSlicePath(line.replace("m.b1.slice.gguf", "m.bin").replace(slice.absolutePath, "x"), dir))
        assertNull(BranchySliceNames.receivedSlicePath("""BRANCHY_EVENT {"type":"progress","phase":"push","done":1,"total":2}""", dir))
        assertNull(BranchySliceNames.receivedSlicePath("plain log line", dir))
    }

    @Test fun batteryGuardHysteresis() {
        assertTrue(BranchyBatteryGuard.shouldPause(true, 14, false, 15, false))
        assertFalse(BranchyBatteryGuard.shouldPause(true, 15, false, 15, false))
        assertTrue(BranchyBatteryGuard.shouldPause(true, 16, false, 15, true))
        assertFalse(BranchyBatteryGuard.shouldPause(true, 18, false, 15, true))
        assertFalse(BranchyBatteryGuard.shouldPause(true, 5, true, 15, true))
        assertFalse(BranchyBatteryGuard.shouldPause(false, 5, false, 15, false))
        assertFalse(BranchyBatteryGuard.shouldPause(true, -1, false, 15, false))
    }

    @Test fun rolesAndInferPortAdvert() {
        assertEquals(BranchyClusterRole.BOTH, BranchyClusterRoles.fromToggles(true, true))
        assertEquals(BranchyClusterRole.INFER, BranchyClusterRoles.fromToggles(false, true))
        assertEquals(BranchyClusterRole.TRAIN, BranchyClusterRoles.fromToggles(false, false))
        val both = BranchyAdvert.buildTxt(BranchyClusterRole.BOTH, "n", null, null, null, 50210, inferPort = 50213)
        assertEquals("50213", both["infer_port"])
        val train = BranchyAdvert.buildTxt(BranchyClusterRole.TRAIN, "n", null, null, null, 50210, inferPort = 50213)
        assertNull(train["infer_port"])
        val attrs = both.mapValues { it.value.toByteArray() }
        assertEquals(50213, BranchyAdvert.parse("n", "10.0.0.2", 50210, attrs, false)!!.inferPort)
    }
}
