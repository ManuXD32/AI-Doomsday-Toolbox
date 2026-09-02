package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdPostRunHealthTest {

    @Test
    fun formatterSanitizesSampleAndBoundsCountersAndOutput() {
        val snapshot = SdPostRunHealthSnapshot(
            javaHeapUsedBytes = Long.MAX_VALUE,
            javaHeapCommittedBytes = Long.MAX_VALUE,
            nativeHeapAllocatedBytes = Long.MAX_VALUE,
            nativeHeapSizeBytes = Long.MAX_VALUE,
            processPssKb = Long.MAX_VALUE,
            processRssKb = Long.MAX_VALUE,
            threadCount = Int.MAX_VALUE,
            fileDescriptorCount = Int.MAX_VALUE,
            activeWorkCount = Int.MAX_VALUE,
            activeProcessCount = Int.MAX_VALUE,
            generationLockHeld = true,
            wakeLockHeld = false
        )

        val details = snapshot.toDetails("completion/../secret\n${"x".repeat(5_000)}")

        assertTrue(details.length <= SdPostRunHealthFormatter.MAX_DETAILS_CHARS)
        assertTrue(details.startsWith("sample=completionsecret"))
        assertFalse(details.contains('/'))
        assertFalse(details.contains('\n'))
        assertTrue(details.contains("threadCount=100000"))
        assertTrue(details.contains("fileDescriptorCount=100000"))
        assertTrue(details.contains("activeWorkCount=100000"))
        assertTrue(details.contains("activeProcessCount=100000"))
        assertTrue(details.contains("generationLockHeld=true"))
        assertTrue(details.contains("wakeLockHeld=false"))
    }

    @Test
    fun formatterOmitsUnavailableCounters() {
        val snapshot = SdPostRunHealthSnapshot(
            javaHeapUsedBytes = null,
            javaHeapCommittedBytes = null,
            nativeHeapAllocatedBytes = null,
            nativeHeapSizeBytes = null,
            processPssKb = null,
            processRssKb = null,
            threadCount = null,
            fileDescriptorCount = null,
            activeWorkCount = 0,
            activeProcessCount = 0,
            generationLockHeld = false,
            wakeLockHeld = false
        )

        val details = snapshot.toDetails("delayed")

        assertTrue(details.contains("sample=delayed"))
        assertFalse(details.contains("javaHeapUsedBytes"))
        assertFalse(details.contains("processPssKb"))
        assertTrue(details.contains("activeWorkCount=0"))
    }
}
